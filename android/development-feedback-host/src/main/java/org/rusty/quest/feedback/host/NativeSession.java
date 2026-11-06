package org.rusty.quest.feedback.host;

import org.rusty.quest.feedback.NativeFeedbackBridge;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** One controller-owned, process-lifetime trusted native session. No unload or owner replacement. */
final class NativeSession {
    private final boolean selected;
    private final String sessionId;
    private final Path session;
    private NativeFeedbackBridge bridge;
    private boolean failed;
    private String ownerToken="none",bridgeDigest="none",generationDigest="none";

    NativeSession(File filesDirectory,boolean selected) throws IOException {
        this.selected=selected;
        if(selected) {
            Path root=physical(filesDirectory.toPath().resolve("native-feedback"));
            Files.createDirectories(root);physical(root);
            sessionId=UUID.randomUUID().toString();
            session=Files.createDirectory(root.resolve(sessionId));
            Files.createDirectory(session.resolve("native-incoming"));
        } else {sessionId="none";session=null;}
    }
    private void requireSelected() {
        if(!selected)throw new IllegalStateException("Native feedback is not selected for this application");
    }
    private void requireReady() {
        requireSelected();
        if(failed)throw new IllegalStateException("Native initialization failed; restart this process");
        if(bridge==null)throw new IllegalStateException("Native bridge is not initialized");
    }
    void initialize(String sha256) throws IOException {
        requireSelected();
        byte[] expected=digest(sha256);
        if(failed)throw new IllegalStateException("Native initialization failed; restart this process");
        if(bridge!=null) {
            if(!bridgeDigest.equals(sha256))throw new IllegalStateException("Native bridge identity already selected");
            return;
        }
        Path library=physical(session.resolve("bridge.so"));
        if(!Files.isRegularFile(library,LinkOption.NOFOLLOW_LINKS)||Files.isWritable(library))
            throw new IOException("Exact readonly received native bridge is required");
        long size=Files.size(library);
        if(size<1||size>NativeFeedbackBridge.STAGED_LIMIT||!MessageDigest.isEqual(hash(library),expected))
            throw new IOException("Native bridge SHA256/size mismatch");
        // All ordinary preflight refusals occur before the sole loading constructor.
        try {
            NativeFeedbackBridge loaded=new NativeFeedbackBridge(library.toFile(),expected,session.toFile());
            bridge=loaded;bridgeDigest=sha256;ownerToken=UUID.randomUUID().toString();
        } catch(LinkageError failure) {
            failed=true;
            throw new IllegalStateException("Native initialization failed; restart this process",failure);
        } catch(IllegalStateException failure) {
            failed=true;
            throw new IllegalStateException("Native initialization failed; restart this process",failure);
        }
    }
    void activate(long generation,String sha256,long expected) throws IOException {
        requireReady();
        if(generation<=0)throw new IllegalArgumentException("Native generation must be positive");
        bridge.activate(generation,digest(sha256),expected);
        generationDigest=sha256;
    }
    void compute(long input) {requireReady();bridge.compute(input);}
    Map<String,String> snapshot() {
        Map<String,String> result=new LinkedHashMap<>();
        result.put("native_selected",Boolean.toString(selected));
        result.put("native_state",!selected?"disabled":failed?"failed":bridge==null?"uninitialized":"ready");
        result.put("native_session_id",sessionId);result.put("native_owner_token",ownerToken);
        result.put("native_bridge_sha256",bridgeDigest);result.put("native_generation_sha256",generationDigest);
        result.put("native_attempt_limit",Integer.toString(NativeFeedbackBridge.ATTEMPT_LIMIT));
        result.put("native_image_byte_limit",Long.toString(NativeFeedbackBridge.IMAGE_LIMIT));
        result.put("native_staged_byte_limit",Long.toString(NativeFeedbackBridge.STAGED_LIMIT));
        NativeFeedbackBridge.Snapshot value=bridge==null?null:bridge.snapshot();
        result.put("native_completed_unsigned",value==null?"none":value.completedUnsigned());
        result.put("native_value",value==null?"none":Long.toString(value.value));
        result.put("native_generation",value==null?"none":Long.toString(value.generation));
        result.put("native_retained_images",value==null?"none":Long.toString(value.retainedImages));
        result.put("native_allocation",value==null?"none":Long.toString(value.allocationToken));
        result.put("native_buffer_hex",value==null?"none":hex(value.payload()));
        return result;
    }
    private static byte[] digest(String text) {
        if(text==null||!text.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Expected lowercase SHA-256");
        byte[] result=new byte[32];
        for(int i=0;i<result.length;i++)result[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);
        return result;
    }
    private static Path physical(Path path) throws IOException {
        Path value=path.toAbsolutePath().normalize();
        if(!value.equals(value.toFile().getCanonicalFile().toPath()))throw new IOException("Native session path alias");
        for(Path part=value;part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw new IOException("Native session symlink");
        return value;
    }
    private static byte[] hash(Path path) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=Files.newInputStream(path)) {
                byte[] bytes=new byte[8192];int count;
                while((count=input.read(bytes))!=-1)digest.update(bytes,0,count);
            }
            return digest.digest();
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static String hex(byte[] bytes) {
        char[] digits="0123456789abcdef".toCharArray();StringBuilder text=new StringBuilder(bytes.length*2);
        for(byte value:bytes)text.append(digits[(value&255)>>>4]).append(digits[value&15]);
        return text.toString();
    }
}
