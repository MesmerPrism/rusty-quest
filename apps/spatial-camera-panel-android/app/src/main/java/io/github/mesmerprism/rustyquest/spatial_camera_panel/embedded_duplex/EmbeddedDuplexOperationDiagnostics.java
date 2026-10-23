package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional app-private observations. No diagnostic can change an operation result or grant authority. */
public final class EmbeddedDuplexOperationDiagnostics {
    private static final int MAX_OPERATIONS=32, MAX_SECONDARY=4, MAX_DETAIL=65536;
    private static final LinkedHashMap<String,JSONObject> operations=new LinkedHashMap<>();
    private static File directory;
    private static long sequence,dropped;
    private EmbeddedDuplexOperationDiagnostics() {}
    public static void initialize(Context context){try{initialize(context.getApplicationContext().getNoBackupFilesDir());}catch(Throwable unavailable){/* No actual operation depends on storage. */}}

    static synchronized void initialize(File root) {
        if(directory!=null)return;
        try {
            File candidate=new File(root,"embedded-duplex-diagnostics");
            if(!candidate.exists()&&!candidate.mkdir())return;
            if(!Files.isDirectory(candidate.toPath(),LinkOption.NOFOLLOW_LINKS))return;
            directory=candidate;
            File retained=new File(directory,"summaries.json");
            if(Files.isRegularFile(retained.toPath(),LinkOption.NOFOLLOW_LINKS)&&retained.length()<=1024*1024){
                JSONObject saved=new JSONObject(new String(Files.readAllBytes(retained.toPath()),StandardCharsets.UTF_8));
                keys(saved,"schema","operations","dropped_count","retention");
                require("rusty.quest.operation_diagnostics_collection.v1".equals(saved.getString("schema")));count(saved.get("dropped_count"));validateRetention(saved.getJSONObject("retention"));
                JSONArray entries=saved.getJSONArray("operations");
                if(entries.length()>MAX_OPERATIONS)throw new IllegalArgumentException("diagnostic retention bounds");
                for(int i=0;i<entries.length();i++){
                    JSONObject item=entries.getJSONObject(i);
                    validateSnapshot(item);
                    operations.put(key(item.getString("operation"),item.getJSONObject("correlation")),item);
                    sequence=Math.max(sequence,item.getJSONObject("primary").getLong("sequence"));
                    JSONArray secondary=item.getJSONArray("secondary");
                    for(int j=0;j<secondary.length();j++)sequence=Math.max(sequence,secondary.getJSONObject(j).getLong("sequence"));
                }
                dropped=saved.optLong("dropped_count",0);
            }
        }catch(Exception unavailable){operations.clear();dropped++;}
    }
    static JSONObject correlation(EmbeddedDuplexOriginals.Record actual) {
        JSONObject value=new JSONObject();
        try {
            for(String name:new String[]{"original_id","request_sha256","outcome_sha256","app_generation","native_generation","attempt_sequence"})value.put(name,JSONObject.NULL);
            if(actual!=null){
                JSONObject row=actual.row;
                for(String name:new String[]{"original_id","request_sha256","outcome_sha256"})if(!row.isNull(name))value.put(name,row.get(name));
                JSONObject actor=row.optJSONObject("actor_binding");
                if(actual.actualAppGeneration>0)value.put("app_generation",actual.actualAppGeneration);
                if(actor!=null){
                    if(!actor.isNull("native_generation"))value.put("native_generation",actor.get("native_generation"));
                }
            }
        }catch(Exception unavailable){/* Null correlation never fabricates an actor. */}
        return value;
    }
    static void observe(String phase,String component,String boundary,Throwable failure) {
        EmbeddedDuplexOriginals.Record actual=EmbeddedDuplexOriginals.capture();
        observe(actual,phase,component,boundary,failure,new JSONObject());
    }
    static void observe(EmbeddedDuplexOriginals.Record actual,String phase,String component,String boundary,
            Throwable failure,JSONObject details) {
        try {
            String operation=actual==null?component:actual.row.getString("operation");
            if("primary".equals(phase)){
                if(java.util.Arrays.asList("peer_stop","peer_revoke","whole_app_close","close_no_media").contains(operation))phase="cleanup";
                else if("abandon_retained_scope".equals(operation))phase="recovery";
            }
            String effect=actual==null?"unobserved":actual.row.optString("effect_state","unobserved");
            record(operation,phase,"java",component,boundary,failure,correlation(actual),effect,
                    "primary".equals(phase)?"unobserved":"pending",details);
        }catch(Throwable unavailable){/* Optional collection cannot replace the actual failure. */}
    }
    private static String key(String operation,JSONObject correlation)throws Exception {
        return digest(operation+"\n"+correlation.optString("original_id","")+"\n"+correlation.optString("app_generation","")+"\n"+correlation.optString("native_generation",""));
    }
    static synchronized void completed(EmbeddedDuplexOriginals.Record actual){
        try{
            JSONObject current=correlation(actual);JSONObject snapshot=operations.get(key(actual.row.getString("operation"),current));
            if(snapshot!=null){snapshot.put("correlation",current);if(directory!=null)atomic(new File(directory,"summaries.json"),collection(null).toString().getBytes(StandardCharsets.UTF_8));}
        }catch(Throwable unavailable){/* Outcome publication never depends on diagnostics. */}
    }
    private static void identifier(String value){if(value==null||!value.matches("[A-Za-z0-9_.-]{1,96}"))throw new IllegalArgumentException("diagnostic identifier");}
    private static void require(boolean value){if(!value)throw new IllegalArgumentException("retained public diagnostic shape");}
    private static void keys(JSONObject object,String... expected){
        java.util.HashSet<String> actual=new java.util.HashSet<>();object.keys().forEachRemaining(actual::add);
        require(actual.equals(new java.util.HashSet<>(java.util.Arrays.asList(expected))));
    }
    private static long count(Object value){require(value instanceof Integer||value instanceof Long);long number=((Number)value).longValue();require(number>=0);return number;}
    private static void validateRetention(JSONObject value)throws Exception{
        keys(value,"max_operations","max_secondary","private_collection_enabled","max_private_context_bytes","max_retained_private_bytes");
        require(count(value.get("max_operations"))==32&&count(value.get("max_secondary"))==4
            &&count(value.get("max_private_context_bytes"))==1024*1024&&count(value.get("max_retained_private_bytes"))==4*1024*1024
            &&value.get("private_collection_enabled") instanceof Boolean);
    }
    private static void validateCorrelation(JSONObject value)throws Exception{
        keys(value,"original_id","request_sha256","outcome_sha256","app_generation","native_generation","attempt_sequence");
        for(String name:new String[]{"original_id","request_sha256","outcome_sha256"})if(!value.isNull(name))
            require(value.get(name) instanceof String&&value.getString(name).matches(name.equals("original_id")?"[0-9a-f]{32}":"[0-9a-f]{64}"));
        for(String name:new String[]{"app_generation","native_generation","attempt_sequence"})if(!value.isNull(name))require(count(value.get(name))>0);
    }
    private static long validateObservation(JSONObject value,String operation,JSONObject correlation)throws Exception{
        keys(value,"schema","sequence","operation","phase","layer","component","boundary","cause","correlation","effect_state","cleanup_state","safe_details","detail_sha256","private_context");
        require("rusty.quest.failure_observation.v1".equals(value.getString("schema"))&&operation.equals(value.getString("operation")));
        require(java.util.Arrays.asList("primary","compensation","cleanup","recovery","transport").contains(value.getString("phase")));
        require(java.util.Arrays.asList("host","provider","java","jni","rust","media").contains(value.getString("layer")));
        for(String name:new String[]{"component","boundary"})identifier(value.getString(name));
        require(java.util.Arrays.asList("not_entered","entered","settled","unknown","unobserved").contains(value.getString("effect_state")));
        require(java.util.Arrays.asList("not_requested","pending","terminal","unknown","unobserved").contains(value.getString("cleanup_state")));
        JSONObject cause=value.getJSONObject("cause");keys(cause,"category","code");identifier(cause.getString("category"));identifier(cause.getString("code"));
        require(java.util.Arrays.asList("unclassified","detail_unavailable",value.getString("boundary")).contains(cause.getString("code")));
        JSONObject subject=value.getJSONObject("correlation");validateCorrelation(subject);
        for(String name:new String[]{"original_id","request_sha256","app_generation","native_generation"})
            if(!subject.isNull(name)&&!correlation.isNull(name))require(subject.get(name).equals(correlation.get(name)));
        safeDetails(value.getJSONObject("safe_details"));
        require(value.isNull("detail_sha256")||(value.get("detail_sha256") instanceof String&&value.getString("detail_sha256").matches("[0-9a-f]{64}")));
        JSONObject descriptor=value.getJSONObject("private_context");keys(descriptor,"status","context_id","bytes","sha256","truncated");
        require(java.util.Arrays.asList("absent","available","unavailable","truncated").contains(descriptor.getString("status")));
        require(descriptor.get("truncated") instanceof Boolean&&count(descriptor.get("bytes"))<=1024*1024);
        if(descriptor.isNull("context_id")){require(descriptor.isNull("sha256")&&descriptor.getLong("bytes")==0);}
        else require(descriptor.get("context_id") instanceof String&&descriptor.getString("context_id").matches("[0-9a-f]{64}")
            &&descriptor.get("sha256") instanceof String&&descriptor.getString("sha256").matches("[0-9a-f]{64}")&&descriptor.getLong("bytes")>0);
        String status=descriptor.getString("status");
        require(!"available".equals(status)||(!descriptor.isNull("context_id")&&!descriptor.getBoolean("truncated")));
        require(!java.util.Arrays.asList("absent","unavailable").contains(status)||(descriptor.isNull("context_id")&&!descriptor.getBoolean("truncated")));
        require(!"truncated".equals(status)||descriptor.getBoolean("truncated"));
        long sequence=count(value.get("sequence"));require(sequence>0);return sequence;
    }
    private static void validateSnapshot(JSONObject value)throws Exception{
        keys(value,"schema","operation","correlation","primary","secondary","dropped_count","retention");
        require("rusty.quest.operation_diagnostics.v1".equals(value.getString("schema")));String operation=value.getString("operation");identifier(operation);
        JSONObject correlation=value.getJSONObject("correlation");validateCorrelation(correlation);count(value.get("dropped_count"));validateRetention(value.getJSONObject("retention"));
        long previous=validateObservation(value.getJSONObject("primary"),operation,correlation);JSONArray secondary=value.getJSONArray("secondary");require(secondary.length()<=MAX_SECONDARY);
        for(int i=0;i<secondary.length();i++){long next=validateObservation(secondary.getJSONObject(i),operation,correlation);require(next>previous);previous=next;}
    }
    static synchronized void record(String operation,String phase,String layer,String component,String boundary,
            Throwable failure,JSONObject correlation,String effect,String cleanup,JSONObject details)throws Exception {
        identifier(operation);identifier(component);identifier(boundary);
        safeDetails(details);
        if(!java.util.Arrays.asList("primary","compensation","cleanup","recovery","transport").contains(phase))throw new IllegalArgumentException("diagnostic phase");
        if(sequence==Long.MAX_VALUE){dropped++;return;}
        long next=++sequence;String id=key(operation,correlation);
        JSONObject privateDescriptor=new JSONObject().put("status","absent").put("context_id",JSONObject.NULL)
                .put("bytes",0).put("sha256",JSONObject.NULL).put("truncated",false);
        String detailHash=null;
        if(failure!=null){
            privateDescriptor.put("status","unavailable");
            try{
                JSONObject detail=privateThrowable(failure);byte[] bytes=detail.toString().getBytes(StandardCharsets.UTF_8);detailHash=digestBytes(bytes);
                if(directory!=null){
                    String context=digest(id+"-"+next);privateDescriptor=store(context,bytes);
                    if(detail.getBoolean("truncated")&&"available".equals(privateDescriptor.getString("status")))privateDescriptor.put("status","truncated").put("truncated",true);
                }
            }catch(Throwable unavailable){/* Keep the precise public boundary even if private detail collection fails. */}
        }
        JSONObject observation=new JSONObject().put("schema","rusty.quest.failure_observation.v1")
                .put("sequence",next).put("operation",operation).put("phase",phase).put("layer",layer)
                .put("component",component).put("boundary",boundary)
                .put("cause",new JSONObject().put("category",category(failure)).put("code",failure==null?boundary:detailHash==null?"detail_unavailable":"unclassified"))
                .put("correlation",new JSONObject(correlation.toString())).put("effect_state",effect).put("cleanup_state",cleanup)
                .put("safe_details",details).put("detail_sha256",detailHash==null?JSONObject.NULL:detailHash).put("private_context",privateDescriptor);
        JSONObject snapshot=operations.get(id);
        if(snapshot==null){
            snapshot=new JSONObject().put("schema","rusty.quest.operation_diagnostics.v1").put("operation",operation)
                .put("correlation",new JSONObject(correlation.toString())).put("primary",observation).put("secondary",new JSONArray())
                .put("dropped_count",0).put("retention",retention());
            operations.put(id,snapshot);
        }else{
            JSONArray previous=snapshot.getJSONArray("secondary"),kept=new JSONArray();
            int start=previous.length()>=MAX_SECONDARY?1:0;
            if(start!=0)snapshot.put("dropped_count",snapshot.getLong("dropped_count")+1);
            for(int i=start;i<previous.length();i++)kept.put(previous.get(i));
            kept.put(observation);snapshot.put("secondary",kept);
            // Outcome correlation can become known after the first failure; do not mutate that observation.
            snapshot.put("correlation",new JSONObject(correlation.toString()));
        }
        while(operations.size()>MAX_OPERATIONS){operations.remove(operations.keySet().iterator().next());dropped++;}
        if(directory!=null){
            try {
                atomic(new File(directory,"summaries.json"),collection(null).toString().getBytes(StandardCharsets.UTF_8));
            }catch(Exception unavailable){dropped++;}
        }
    }
    private static JSONObject retention()throws Exception{return new JSONObject().put("max_operations",MAX_OPERATIONS)
        .put("max_secondary",MAX_SECONDARY).put("private_collection_enabled",directory!=null)
        .put("max_private_context_bytes",1024*1024).put("max_retained_private_bytes",4*1024*1024);}
    static synchronized JSONObject collection(String original)throws Exception {
        JSONArray rows=new JSONArray();for(JSONObject snapshot:operations.values())
            if(original==null||original.equals(snapshot.getJSONObject("correlation").optString("original_id"))){
                JSONObject copy=new JSONObject(snapshot.toString());refreshContext(copy.getJSONObject("primary"));
                JSONArray secondary=copy.getJSONArray("secondary");for(int i=0;i<secondary.length();i++)refreshContext(secondary.getJSONObject(i));rows.put(copy);
            }
        return new JSONObject().put("schema","rusty.quest.operation_diagnostics_collection.v1").put("operations",rows)
                .put("dropped_count",dropped).put("retention",retention());
    }
    private static void refreshContext(JSONObject observation)throws Exception{
        JSONObject descriptor=observation.getJSONObject("private_context");
        if(!descriptor.isNull("context_id")){
            String id=descriptor.getString("context_id"),hash=descriptor.optString("sha256","");long bytes=descriptor.optLong("bytes",-1);
            boolean valid=directory!=null&&id.matches("[0-9a-f]{64}")&&hash.matches("[0-9a-f]{64}")&&bytes>=0&&bytes<=1024*1024;
            File file=valid?new File(directory,id+".json"):null;
            try{valid=valid&&Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)&&file.length()==bytes
                    &&digestBytes(Files.readAllBytes(file.toPath())).equals(hash);}catch(Throwable unavailable){valid=false;}
            if(!valid)observation.put("private_context",descriptor("unavailable",null,0,null,false));
        }
    }
    private static void safeDetails(JSONObject details)throws Exception{
        if(details.toString().length()>2048)throw new IllegalArgumentException("safe diagnostic detail bounds");
        java.util.Iterator<String> keys=details.keys();while(keys.hasNext()){
            String key=keys.next();identifier(key);Object value=details.get(key);
            if(java.util.Arrays.asList("receipt_known","native_pending","native_cause_present").contains(key))require(value instanceof Boolean);
            else if(java.util.Arrays.asList("expected_generation","observed_generation").contains(key)){
                require(value instanceof Integer||value instanceof Long);require(((Number)value).longValue()>=-1);
            }else throw new IllegalArgumentException("safe diagnostic detail field");
        }
    }
    /** Read-only plain-data retrieval remains available after native host/fence retirement. */
    public static String read(Context context,String original,String challenge)throws Exception {
        if(challenge==null||!challenge.matches("[0-9a-f]{32}")||(original!=null&&!original.matches("[0-9a-f]{32}")))throw new IllegalArgumentException("diagnostic selector");
        initialize(context);
        JSONObject value=new JSONObject().put("schema","rusty.quest.embedded_duplex.operation_diagnostics.v1")
            .put("challenge",challenge).put("original_id",original==null?JSONObject.NULL:original).put("java_observation",collection(original));
        try {
            JSONObject nativeValue=new JSONObject(EmbeddedDuplexNative.operationDiagnostics(original));
            if(!"rusty.quest.operation_diagnostics_collection.v1".equals(nativeValue.getString("schema")))throw new IllegalArgumentException("native diagnostic schema");
            JSONArray snapshots=nativeValue.getJSONArray("operations");
            if(snapshots.length()>MAX_OPERATIONS)throw new IllegalArgumentException("native diagnostic bounds");
            JSONArray selected=new JSONArray();
            for(int i=0;i<snapshots.length();i++){
                JSONObject snapshot=snapshots.getJSONObject(i),primary=snapshot.optJSONObject("primary");
                if(original!=null&&!original.equals(snapshot.getJSONObject("correlation").optString("original_id")))continue;
                if(primary!=null)refreshContext(primary);
                JSONArray secondary=snapshot.getJSONArray("secondary");if(secondary.length()>MAX_SECONDARY)throw new IllegalArgumentException("native secondary bounds");
                for(int j=0;j<secondary.length();j++)refreshContext(secondary.getJSONObject(j));
                selected.put(snapshot);
            }
            nativeValue.put("operations",selected);
            value.put("native_observation",nativeValue).put("native_status","available");
        }catch(Throwable unavailable){value.put("native_observation",JSONObject.NULL).put("native_status","unavailable");}
        return boundedRead(value);
    }
    /** Trim only the owned read copy; retained primaries/private evidence are never rewritten. */
    private static String boundedRead(JSONObject wrapper)throws Exception{
        while(true){
            String encoded=wrapper.toString();
            if(encoded.length()<=65536&&encoded.getBytes(StandardCharsets.UTF_8).length<=128*1024)return encoded;
            JSONObject selected=null;int largest=0;
            for(String name:new String[]{"java_observation","native_observation"}){
                JSONObject collection=wrapper.optJSONObject(name);if(collection==null)continue;
                JSONArray rows=collection.getJSONArray("operations");
                if(rows.length()>1&&collection.toString().length()>largest){selected=collection;largest=collection.toString().length();}
            }
            if(selected!=null){
                JSONArray rows=selected.getJSONArray("operations"),kept=new JSONArray();int oldest=0;long oldestSequence=Long.MAX_VALUE;
                for(int i=0;i<rows.length();i++){
                    JSONObject item=rows.getJSONObject(i);long latest=item.getJSONObject("primary").getLong("sequence");JSONArray later=item.getJSONArray("secondary");
                    for(int j=0;j<later.length();j++)latest=Math.max(latest,later.getJSONObject(j).getLong("sequence"));
                    if(latest<oldestSequence){oldestSequence=latest;oldest=i;}
                }
                for(int i=0;i<rows.length();i++)if(i!=oldest)kept.put(rows.get(i));
                selected.put("operations",kept).put("dropped_count",selected.getLong("dropped_count")+1);continue;
            }
            // Under the producer bounds a single requested Original fits. Defensive fallback
            // drops an oversized producer collection explicitly rather than crossing Binder limits.
            for(String name:new String[]{"native_observation","java_observation"}){
                JSONObject collection=wrapper.optJSONObject(name);if(collection!=null&&collection.getJSONArray("operations").length()>0){
                    collection.put("dropped_count",collection.getLong("dropped_count")+collection.getJSONArray("operations").length()).put("operations",new JSONArray());selected=collection;break;
                }
            }
            if(selected==null)throw new IllegalArgumentException("diagnostic read metadata bounds");
        }
    }
    public static void providerFailure(Context context,String operation,Throwable failure){
        try{initialize(context);record(operation,"transport","provider","operator","supported_read",failure,
            correlation(null),"unobserved","unobserved",new JSONObject());}catch(Throwable unavailable){/* Best effort. */}
    }
    public static String safeFailureCategory(Throwable failure){return category(failure);}
    /** Pure bounded private serialization for JNI; no disk, runtime or authority access. */
    public static String nativeThrowableContext(Throwable failure){
        try{return privateThrowable(failure).toString();}
        catch(Throwable unavailable){return "{\"schema\":\"rusty.quest.java_failure_context.v1\",\"throwables\":[],\"truncated\":false,\"messages_retained\":false,\"status\":\"unavailable\"}";}
    }
    /** Fixed optional JNI failure sink. The native producer must omit private signing keys. */
    public static synchronized String storeNativeContext(String opaqueId,String boundedPrivateJson){
        try {
            if(opaqueId==null||!opaqueId.matches("[0-9a-f]{64}")||boundedPrivateJson==null)throw new IllegalArgumentException("private context selector");
            byte[] bytes=boundedPrivateJson.getBytes(StandardCharsets.UTF_8);
            if(bytes.length>1024*1024)return descriptor("truncated",null,0,null,true).toString();
            rejectSecretFields(new JSONObject(boundedPrivateJson),0);
            return store(opaqueId,bytes).toString();
        }catch(Throwable unavailable){return "{\"status\":\"unavailable\",\"context_id\":null,\"bytes\":0,\"sha256\":null,\"truncated\":false}";}
    }
    public static synchronized boolean evictNativeContext(String opaqueId){
        try{
            if(directory==null||opaqueId==null||!opaqueId.matches("[0-9a-f]{64}"))return false;
            File file=new File(directory,opaqueId+".json");
            if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return true;
            if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))return false;
            Files.delete(file.toPath());return !Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS);
        }catch(Throwable unavailable){return false;}
    }
    private static JSONObject descriptor(String status,String id,long bytes,String hash,boolean truncated)throws Exception{
        return new JSONObject().put("status",status).put("context_id",id==null?JSONObject.NULL:id).put("bytes",bytes)
            .put("sha256",hash==null?JSONObject.NULL:hash).put("truncated",truncated);
    }
    private static JSONObject store(String id,byte[] bytes)throws Exception{
        if(directory==null)return descriptor("unavailable",null,0,null,false);
        File target=new File(directory,id+".json");
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)||target.length()>1024*1024||!java.util.Arrays.equals(Files.readAllBytes(target.toPath()),bytes))
                return descriptor("unavailable",null,0,null,false);
        }else{
            File[] files=directory.listFiles(file->file.getName().matches("[0-9a-f]{64}\\.json"));
            if(files==null)return descriptor("unavailable",null,0,null,false);
            java.util.Arrays.sort(files,java.util.Comparator.comparingLong(File::lastModified));
            long total=bytes.length;for(File file:files)total+=file.length();
            int remaining=files.length+1;
            for(File file:files){if(total<=4*1024*1024&&remaining<=256)break;long size=file.length();Files.delete(file.toPath());total-=size;remaining--;}
            atomic(target,bytes);
        }
        return descriptor("available",id,bytes.length,digestBytes(bytes),false);
    }
    private static void rejectSecretFields(Object value,int depth)throws Exception{
        if(depth>32)throw new IllegalArgumentException("private context nesting");
        if(value instanceof JSONObject){
            JSONObject object=(JSONObject)value;java.util.Iterator<String> keys=object.keys();while(keys.hasNext()){
                String key=keys.next(),lower=key.toLowerCase(java.util.Locale.ROOT);
                if(lower.contains("private_key")||lower.equals("signing_key")||lower.equals("signing_key_bytes")||lower.equals("signingkey")
                    ||lower.contains("secret_key")||lower.contains("password")||lower.contains("keystore_secret")||lower.equals("seed"))throw new IllegalArgumentException("private signing material excluded");
                rejectSecretFields(object.get(key),depth+1);
            }
        }else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)rejectSecretFields(array.get(i),depth+1);}
    }
    static String category(Throwable failure){
        for(int i=0;failure!=null&&i<8;i++,failure=failure.getCause()){
            if(failure instanceof SecurityException)return "permission";
            if(failure instanceof java.util.concurrent.TimeoutException||failure instanceof java.net.SocketTimeoutException)return "timeout";
            if(failure instanceof InterruptedException||failure instanceof java.util.concurrent.CancellationException)return "cancelled";
            if(failure instanceof java.io.IOException)return "io";
            if(failure.getClass().getName().equals("android.media.MediaCodec$CodecException"))return "codec";
            if(failure instanceof IllegalArgumentException)return "invalid_input";
        }
        return "unclassified";
    }
    /** Bounded private messages and cause edges; public output contains none of this data. */
    private static JSONObject privateThrowable(Throwable failure)throws Exception{
        JSONArray nodes=new JSONArray();java.util.IdentityHashMap<Throwable,Integer> seen=new java.util.IdentityHashMap<>();
        java.util.ArrayDeque<Throwable> pending=new java.util.ArrayDeque<>();pending.add(failure);boolean truncated=false;
        seen.put(failure,0);
        while(!pending.isEmpty()&&nodes.length()<12){
            Throwable current=pending.remove();
            JSONObject node=new JSONObject().put("index",seen.get(current)).put("class",current.getClass().getName());
            String message=current.getMessage();node.put("message_sha256",message==null?JSONObject.NULL:digest(message));
            boolean redacted=message!=null&&java.util.regex.Pattern.compile("(?is)-----BEGIN[^-]*PRIVATE KEY-----|private[_ -]?key|signing[_ -]?key|secret[_ -]?key|keystore[_ -]?secret|password|\\bseed\\b").matcher(message).find();
            String retained=message==null?null:redacted?"[redacted private signing material]":bounded(message,2048);
            boolean messageTruncated=!redacted&&message!=null&&!message.equals(retained);
            node.put("message",retained==null?JSONObject.NULL:retained).put("message_retained",retained!=null)
                .put("message_redacted",redacted).put("message_truncated",messageTruncated);truncated|=messageTruncated;
            JSONArray frames=new JSONArray();StackTraceElement[] trace=current.getStackTrace();
            for(int i=0;i<Math.min(trace.length,4);i++)frames.put(bounded(trace[i].toString(),256));
            node.put("frames",frames).put("frames_truncated",trace.length>4).put("suppressed_count",current.getSuppressed().length);
            Throwable cause=current.getCause();
            if(cause!=null&&!seen.containsKey(cause)&&seen.size()<12){seen.put(cause,seen.size());pending.add(cause);}
            node.put("cause_index",cause!=null&&seen.containsKey(cause)?seen.get(cause):JSONObject.NULL);
            JSONArray suppressedIndices=new JSONArray();
            for(Throwable suppressed:current.getSuppressed()){
                if(!seen.containsKey(suppressed)&&seen.size()<12){seen.put(suppressed,seen.size());pending.add(suppressed);}
                if(seen.containsKey(suppressed))suppressedIndices.put(seen.get(suppressed));else truncated=true;
            }
            if(cause!=null&&!seen.containsKey(cause))truncated=true;
            node.put("suppressed_indices",suppressedIndices);nodes.put(node);
        }
        truncated|=!pending.isEmpty();JSONObject detail=new JSONObject().put("schema","rusty.quest.java_failure_context.v1")
            .put("throwables",nodes).put("truncated",truncated).put("messages_retained",true);
        if(detail.toString().getBytes(StandardCharsets.UTF_8).length>MAX_DETAIL)
            return new JSONObject().put("schema","rusty.quest.java_failure_context.v1").put("throwables",new JSONArray()).put("truncated",true).put("messages_retained",false);
        return detail;
    }
    private static String bounded(String text,int maxBytes){
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length<=maxBytes)return text;
        int end=maxBytes;while(end>0&&(bytes[end]&0xc0)==0x80)end--;
        return new String(bytes,0,end,StandardCharsets.UTF_8);
    }
    private static void atomic(File target,byte[] bytes)throws Exception{
        File temp=new File(target.getParentFile(),target.getName()+".tmp");
        try{Files.write(temp.toPath(),bytes);Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        finally{Files.deleteIfExists(temp.toPath());}
    }
    private static String digest(String value)throws Exception{return digestBytes(value.getBytes(StandardCharsets.UTF_8));}
    private static String digestBytes(byte[] bytes)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();
        for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
}
