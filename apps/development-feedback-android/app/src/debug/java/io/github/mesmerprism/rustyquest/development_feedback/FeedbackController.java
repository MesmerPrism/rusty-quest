package io.github.mesmerprism.rustyquest.development_feedback;

import org.rusty.quest.feedback.ArtifactStore;
import org.rusty.quest.feedback.GenerationLoader;
import org.rusty.quest.feedback.ModuleHost;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** One Application owns this controller; Activity instances and the debug provider share it. */
public final class FeedbackController {
    public static final int BUFFER_BYTES=16;
    private static final String ENTRY="feedbackplugin.Module";
    private final String applicationToken=UUID.randomUUID().toString();
    private final String processToken;
    private final File incoming,generations;
    private final ModuleHost host;
    private final boolean dex;
    private final Map<String,String> selection;
    private long activityCreations;
    private String latestActivity="none";
    private Long lastResult;

    public FeedbackController(File filesDirectory,GenerationLoader loader,boolean dex,String processToken) throws IOException {
        this(filesDirectory,loader,dex,processToken,null);
    }
    public FeedbackController(File filesDirectory,GenerationLoader loader,boolean dex,String processToken,Map<String,String> selection) throws IOException {
        if(filesDirectory==null||loader==null||processToken==null||processToken.isEmpty())throw new IllegalArgumentException("Missing resident inputs");
        if(selection==null&&dex)throw new IllegalArgumentException("DEX consumer requires its packaged selection");
        this.selection=selection==null?Collections.<String,String>emptyMap():validatedSelection(selection);
        File root=filesDirectory.getCanonicalFile();
        incoming=containedDirectory(root,"incoming");generations=containedDirectory(root,"generations");
        this.dex=dex;this.processToken=processToken;
        host=new ModuleHost(loader,64,new byte[BUFFER_BYTES]);
    }
    private static Map<String,String> validatedSelection(Map<String,String> input) {
        Map<String,String> value=new LinkedHashMap<>(input);
        exact(value,"project_id","lock_revision","lock_fingerprint","feature_lock_sha256","runtime_profile_sha256","selected_features");
        if(!value.get("project_id").matches("[a-z0-9][a-z0-9-]{1,127}")||!value.get("lock_revision").matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Invalid selected project/revision");
        try{Long.parseLong(value.get("lock_revision"));}catch(NumberFormatException bad){throw new IllegalArgumentException("Lock revision outside signed64",bad);}
        for(String key:Arrays.asList("lock_fingerprint","feature_lock_sha256","runtime_profile_sha256"))if(!value.get(key).matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid selected identity digest");
        if(!"development-feedback-core,development-feedback-debug-host".equals(value.get("selected_features")))throw new IllegalArgumentException("Unexpected selected feature closure");
        return Collections.unmodifiableMap(value);
    }
    private static File containedDirectory(File root,String name) throws IOException {
        File result=new File(root,name);
        if(Files.isSymbolicLink(result.toPath())||!result.getCanonicalFile().equals(result.getAbsoluteFile()))throw new IllegalArgumentException("Private directory alias refused");
        Files.createDirectories(result.toPath());return result;
    }
    public synchronized String activityCreated() {
        activityCreations++;latestActivity=UUID.randomUUID().toString();return latestActivity;
    }
    /** Closed typed development operations. This is trusted code loading, not a Java sandbox. */
    public synchronized Map<String,String> execute(String method,Map<String,String> input) throws IOException {
        if(method==null||input==null)throw new IllegalArgumentException("Method and fields required");
        Map<String,String> args=new LinkedHashMap<>(input);
        switch(method) {
            case "status": exact(args);break;
            case "compute":
                exact(args,"input");long value=number(args,"input");
                lastResult=host.compute(value);break;
            case "activate":
                exact(args,"generation","sha256","probe_input","probe_counter","probe_initial","probe_result","probe_expected");
                String id=generation(args.get("generation"));
                String digest=args.get("sha256");
                if(digest==null||!digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Expected lowercase SHA-256");
                ModuleHost.Probe probe=new ModuleHost.Probe(number(args,"probe_input"),number(args,"probe_counter"),hex(args.get("probe_initial")),number(args,"probe_result"),hex(args.get("probe_expected")));
                File source=new File(incoming,id+".jar");
                if(Files.isSymbolicLink(source.toPath())||!source.getCanonicalFile().equals(source.getAbsoluteFile())||!source.isFile())throw new IllegalArgumentException("Exact incoming generation is absent or aliased");
                ArtifactStore.Artifact artifact;
                try(InputStream stream=new FileInputStream(source)){artifact=ArtifactStore.stage(generations,id,stream,digest,dex);}
                host.activate(artifact,ENTRY,id,probe);break;
            case "select":
                exact(args,"generation");host.selectRetained(generation(args.get("generation")));break;
            default: throw new IllegalArgumentException("Unsupported feedback method");
        }
        return snapshot();
    }
    private Map<String,String> snapshot() {
        Map<String,String> result=new LinkedHashMap<>();
        result.put("schema","rusty.quest.development_feedback.status.v1");
        result.put("selection_mode",selection.isEmpty()?"host-only-unbound":"packaged-lock-and-runtime-profile");
        for(Map.Entry<String,String> field:selection.entrySet())result.put("selection_"+field.getKey(),field.getValue());
        result.put("process_token",processToken);result.put("application_token",applicationToken);
        result.put("latest_activity_token",latestActivity);result.put("activity_creations",Long.toString(activityCreations));
        result.put("counter",Long.toString(host.counter()));result.put("buffer_allocation",Long.toString(host.bufferAllocationId()));
        byte[] bytes=host.bufferSnapshot();CRC32 checksum=new CRC32();checksum.update(bytes);
        result.put("buffer_hex",toHex(bytes));result.put("buffer_crc32",Long.toHexString(checksum.getValue()));
        result.put("attempted_loads",Integer.toString(host.attemptedLoads()));
        String active="none";try{active=host.activeGenerationId();}catch(IllegalStateException empty){/* no bundled implementation */}
        result.put("generation",active);result.put("result",lastResult==null?"none":Long.toString(lastResult));
        return Collections.unmodifiableMap(result);
    }
    private static void exact(Map<String,String> args,String... expected) {
        Set<String> names=new HashSet<>(Arrays.asList(expected));
        if(!args.keySet().equals(names))throw new IllegalArgumentException("Unexpected or missing feedback fields");
        for(String value:args.values())if(value==null||value.length()>128)throw new IllegalArgumentException("Missing or oversized feedback value");
    }
    private static long number(Map<String,String> args,String key) {
        String text=args.get(key);
        if(text==null||!text.matches("-?(0|[1-9][0-9]{0,18})"))throw new IllegalArgumentException("Expected bounded decimal: "+key);
        try{return Long.parseLong(text);}catch(NumberFormatException bad){throw new IllegalArgumentException("Decimal outside signed64 range: "+key,bad);}
    }
    private static String generation(String value) {
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("Invalid generation identity");return value;
    }
    private static byte[] hex(String value) {
        if(value==null||!value.matches("[0-9a-f]{"+(BUFFER_BYTES*2)+"}"))throw new IllegalArgumentException("Expected exact16-byte lowercase hex");
        byte[] bytes=new byte[BUFFER_BYTES];
        for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return bytes;
    }
    private static String toHex(byte[] bytes) {
        StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
    }
}
