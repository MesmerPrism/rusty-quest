package org.rusty.quest.feedback.host;

import org.rusty.quest.feedback.ArtifactStore;
import org.rusty.quest.feedback.GenerationLoader;
import org.rusty.quest.feedback.ModuleHost;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/**
 * A process-owned trusted-development host with no Activity or renderer references.
 * The consuming application must explicitly select its debug capability and own
 * package/feature admission. This class grants no package, path or device authority.
 */
public final class ResidentFeedbackHost {
    public static final int BUFFER_BYTES=16;
    private static final String ENTRY="feedbackplugin.Module";
    private final String applicationToken=UUID.randomUUID().toString();
    private final String hostSessionId=UUID.randomUUID().toString();
    private final String processToken;
    private final File incoming,generations;
    private final ModuleHost host;
    private final NativeSession nativeSession;
    private final Set<String> stagedIds=new HashSet<>();
    private int stagingAttempts;
    private long stagedBytes;
    private static final int STAGING_LIMIT=64, IMAGE_LIMIT=16*1024*1024;
    private static final long STAGED_BYTES_LIMIT=64L*1024*1024;
    private final boolean dex;
    private Long lastResult;

    public ResidentFeedbackHost(File filesDirectory,GenerationLoader loader,String processToken)throws IOException {
        this(filesDirectory,loader,true,processToken);
    }
    /** Non-DEX mode is only for the actual JVM conformance loader. */
    public ResidentFeedbackHost(File filesDirectory,GenerationLoader loader,boolean dex,String processToken)throws IOException {
        if(filesDirectory==null||loader==null||processToken==null||processToken.isEmpty())throw new IllegalArgumentException("Missing resident inputs");
        File files=filesDirectory.getAbsoluteFile();
        if(!files.equals(files.getCanonicalFile())||Files.isSymbolicLink(files.toPath()))throw new IOException("App files path alias");
        File parent=containedDirectory(files,"development-feedback");
        File root=new File(parent,hostSessionId);
        Files.createDirectory(root.toPath());
        incoming=containedDirectory(root,"incoming");generations=containedDirectory(root,"generations");
        this.dex=dex;this.processToken=processToken;
        host=new ModuleHost(loader,64,new byte[BUFFER_BYTES]);
        nativeSession=new NativeSession(root,true);
    }
    private static File containedDirectory(File root,String name)throws IOException {
        File result=new File(root,name).getAbsoluteFile();
        if(!result.equals(result.getCanonicalFile())||Files.isSymbolicLink(result.toPath()))throw new IOException("Fixed feedback directory alias");
        Files.createDirectories(result.toPath());return result;
    }
    /** UI and typed local adapters call this same closed operation set. */
    public synchronized Map<String,String> execute(String method,Map<String,String> input)throws IOException {
        if(method==null||input==null)throw new IllegalArgumentException("Method and fields required");
        Map<String,String> args=new LinkedHashMap<>(input);
        switch(method){
            case "status":exact(args);break;
            case "native_status":exact(args);break;
            case "native_init":exact(args,"sha256");nativeSession.initialize(args.get("sha256"));break;
            case "native_activate":exact(args,"generation","sha256","probe_result");nativeSession.activate(number(args,"generation"),args.get("sha256"),number(args,"probe_result"));break;
            case "native_compute":exact(args,"input");nativeSession.compute(number(args,"input"));break;
            case "compute":exact(args,"input");lastResult=host.compute(number(args,"input"));break;
            case "select":exact(args,"generation");host.selectRetained(generation(args.get("generation")));break;
            case "activate":
                exact(args,"generation","sha256","probe_input","probe_counter","probe_initial","probe_result","probe_expected");
                String id=generation(args.get("generation"));String digest=args.get("sha256");
                if(digest==null||!digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Expected lowercase SHA256");
                ModuleHost.Probe probe=new ModuleHost.Probe(number(args,"probe_input"),number(args,"probe_counter"),hex(args.get("probe_initial")),number(args,"probe_result"),hex(args.get("probe_expected")));
                File source=new File(incoming,id+".jar");
                if(Files.isSymbolicLink(source.toPath())||!source.getCanonicalFile().equals(source.getAbsoluteFile())||!source.isFile())throw new IllegalArgumentException("Exact incoming generation absent or aliased");
                if(stagedIds.contains(id)||stagingAttempts>=STAGING_LIMIT)throw new IllegalStateException("Staging identity exhausted; use a fresh generation or restart");
                // Charge failed candidates too, before any retained file can be created.
                stagedIds.add(id);stagingAttempts++;
                long bytes=Files.size(source.toPath());
                if(bytes<1||bytes>IMAGE_LIMIT||bytes>STAGED_BYTES_LIMIT-stagedBytes)throw new IllegalArgumentException("Staging byte budget exceeded");
                stagedBytes+=bytes;
                ArtifactStore.Artifact artifact;
                try(InputStream stream=new FileInputStream(source)){artifact=ArtifactStore.stage(generations,id,stream,digest,dex);}
                host.activate(artifact,ENTRY,id,probe);break;
            default:throw new IllegalArgumentException("Unsupported feedback operation");
        }
        return snapshot();
    }
    public synchronized Map<String,String> snapshot(){
        Map<String,String> result=new LinkedHashMap<>();
        result.put("schema","rusty.quest.development_feedback.host_status.v1");
        result.put("host_session_id",hostSessionId);result.put("process_token",processToken);result.put("application_token",applicationToken);
        result.put("counter",Long.toString(host.counter()));result.put("buffer_allocation",Long.toString(host.bufferAllocationId()));
        byte[] bytes=host.bufferSnapshot();CRC32 crc=new CRC32();crc.update(bytes);
        result.put("buffer_hex",toHex(bytes));result.put("buffer_crc32",Long.toHexString(crc.getValue()));
        result.put("attempted_loads",Integer.toString(host.attemptedLoads()));
        result.put("staging_attempts",Integer.toString(stagingAttempts));result.put("staged_bytes",Long.toString(stagedBytes));
        String active="none";try{active=host.activeGenerationId();}catch(IllegalStateException empty){/* no bundled implementation */}
        result.put("generation",active);result.put("result",lastResult==null?"none":Long.toString(lastResult));
        result.putAll(nativeSession.snapshot());return Collections.unmodifiableMap(result);
    }
    private static void exact(Map<String,String> args,String... expected){
        if(!args.keySet().equals(new HashSet<>(Arrays.asList(expected))))throw new IllegalArgumentException("Unexpected or missing feedback fields");
        for(String value:args.values())if(value==null||value.length()>128)throw new IllegalArgumentException("Missing or oversized feedback value");
    }
    private static long number(Map<String,String> args,String key){
        String text=args.get(key);
        if(text==null||"-0".equals(text)||!text.matches("-?(0|[1-9][0-9]{0,18})"))throw new IllegalArgumentException("Expected canonical decimal: "+key);
        try{return Long.parseLong(text);}catch(NumberFormatException bad){throw new IllegalArgumentException("Decimal outside signed64",bad);}
    }
    private static String generation(String text){if(text==null||!text.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("Invalid generation identity");return text;}
    private static byte[] hex(String text){
        if(text==null||!text.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("Expected exact16-byte lowercase hex");
        byte[] value=new byte[BUFFER_BYTES];for(int n=0;n<value.length;n++)value[n]=(byte)Integer.parseInt(text.substring(n*2,n*2+2),16);return value;
    }
    private static String toHex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
}
