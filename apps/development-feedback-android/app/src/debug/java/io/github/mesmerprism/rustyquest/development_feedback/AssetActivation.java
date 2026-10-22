package io.github.mesmerprism.rustyquest.development_feedback;

import android.content.res.AssetManager;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Projects the owner-resolved, packaged selection; the workflow owner resolves full feature policy. */
final class AssetActivation {
    private static final String PACKAGE="io.github.mesmerprism.rustyquest.development_feedback";
    private AssetActivation() { }
    static Map<String,String> read(AssetManager assets) throws IOException {
        byte[] lockBytes=bytes(assets,"feedback-feature-lock.json");
        byte[] runtimeBytes=bytes(assets,"feedback-runtime-profile.json");
        try {
            JSONObject lock=new JSONObject(new String(lockBytes,StandardCharsets.UTF_8));
            if(!"rusty.morphospace.feature_lock.v2".equals(lock.getString("schema"))||
                !"quest-development-feedback".equals(lock.getString("project_id"))||
                !"disabled".equals(lock.getString("default_activation"))||
                !"selected-lock-and-runtime-input".equals(lock.getString("activation_rule")))throw new IOException("Unexpected packaged feature lock");
            JSONArray features=lock.getJSONArray("selected_features");
            Set<String> selected=new HashSet<>();for(int i=0;i<features.length();i++)selected.add(features.getString(i));
            if(features.length()!=2||!selected.equals(new HashSet<>(Arrays.asList("development-feedback-core","development-feedback-debug-host"))))throw new IOException("Unexpected packaged feature selection");
            JSONObject runtime=new JSONObject(new String(runtimeBytes,StandardCharsets.UTF_8));
            Set<String> runtimeKeys=new HashSet<>();for(Iterator<String> keys=runtime.keys();keys.hasNext();)runtimeKeys.add(keys.next());
            if(!runtimeKeys.equals(new HashSet<>(Arrays.asList("schema","app_lane","owner_package","property_policy","properties")))||
                !"rusty.quest.development_feedback.runtime_profile.v1".equals(runtime.getString("schema"))||
                !"development-feedback-android".equals(runtime.getString("app_lane"))||
                !PACKAGE.equals(runtime.getString("owner_package"))||!"none".equals(runtime.getString("property_policy"))||
                runtime.getJSONArray("properties").length()!=0)throw new IOException("Unexpected packaged runtime input");
            Map<String,String> result=new LinkedHashMap<>();
            result.put("project_id",lock.getString("project_id"));result.put("lock_revision",lock.get("revision").toString());
            result.put("lock_fingerprint",lock.getString("lock_fingerprint"));
            result.put("feature_lock_sha256",sha256(lockBytes));result.put("runtime_profile_sha256",sha256(runtimeBytes));
            result.put("selected_features","development-feedback-core,development-feedback-debug-host");
            return result;
        } catch(JSONException failure) { throw new IOException("Malformed packaged activation input",failure); }
    }
    private static byte[] bytes(AssetManager assets,String name) throws IOException {
        try(InputStream in=assets.open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int count;
            while((count=in.read(buffer))!=-1) { if(out.size()+count>1048576)throw new IOException("Oversized packaged activation input");out.write(buffer,0,count); }
            return out.toByteArray();
        }
    }
    private static String sha256(byte[] value) {
        try { StringBuilder result=new StringBuilder();for(byte part:MessageDigest.getInstance("SHA-256").digest(value))result.append(String.format(Locale.ROOT,"%02x",part&255));return result.toString(); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
}
