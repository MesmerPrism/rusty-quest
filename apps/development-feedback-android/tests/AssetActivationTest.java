package io.github.mesmerprism.rustyquest.development_feedback;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/** Host parser regression with an explicit real JSON implementation, never the Android SDK stubs. */
public final class AssetActivationTest {
    private static int assertions;
    private static void check(boolean value,String message) {
        assertions++;if(!value)throw new AssertionError(message);
    }
    private static byte[] encode(JSONObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String digest(byte[] bytes) throws Exception {
        StringBuilder result=new StringBuilder();
        for(byte part:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(Locale.ROOT,"%02x",part&255));
        return result.toString();
    }
    private static void reject(byte[] lock,byte[] runtime,String reason) throws Exception {
        boolean refused=false;
        try { AssetActivation.parse(lock,runtime); } catch(IOException expected) { refused=true; }
        check(refused,"packaged input accepted: "+reason);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Feature lock and runtime profile paths required");
        byte[] lock=Files.readAllBytes(Paths.get(args[0]));
        byte[] runtime=Files.readAllBytes(Paths.get(args[1]));
        JSONObject selectedLock=new JSONObject(new String(lock,StandardCharsets.UTF_8));
        JSONObject selectedRuntime=new JSONObject(new String(runtime,StandardCharsets.UTF_8));
        System.out.println("JSON implementation: "+JSONObject.class.getProtectionDomain().getCodeSource().getLocation());
        // This first real projection must accept the owner-resolved published WEF schema.
        Map<String,String> projected=AssetActivation.parse(lock,runtime);
        check(projected.keySet().equals(new HashSet<>(Arrays.asList("project_id","lock_revision","lock_fingerprint",
            "feature_lock_sha256","runtime_profile_sha256","selected_features"))),"projection fields changed");
        check("quest-development-feedback".equals(projected.get("project_id")),"wrong project projection");
        check(selectedLock.get("revision").toString().equals(projected.get("lock_revision")),"wrong revision projection");
        check(selectedLock.getString("lock_fingerprint").equals(projected.get("lock_fingerprint")),"wrong semantic fingerprint projection");
        check(digest(lock).equals(projected.get("feature_lock_sha256")),"feature lock digest is not exact raw bytes");
        check(digest(runtime).equals(projected.get("runtime_profile_sha256")),"runtime digest is not exact raw bytes");
        check("development-feedback-core,development-feedback-debug-host".equals(projected.get("selected_features")),"wrong feature projection");

        for(String key:Arrays.asList("schema","project_id","default_activation","activation_rule")) {
            JSONObject wrong=new JSONObject(new String(lock,StandardCharsets.UTF_8));wrong.put(key,"wrong");
            reject(encode(wrong),runtime,"wrong lock "+key);
            wrong.remove(key);reject(encode(wrong),runtime,"missing lock "+key);
        }
        for(String key:Arrays.asList("revision","lock_fingerprint","selected_features")) {
            JSONObject missing=new JSONObject(new String(lock,StandardCharsets.UTF_8));missing.remove(key);
            reject(encode(missing),runtime,"missing lock "+key);
        }
        JSONObject wrongFeatures=new JSONObject(new String(lock,StandardCharsets.UTF_8));
        wrongFeatures.put("selected_features",new JSONArray().put("development-feedback-core").put("development-feedback-core"));
        reject(encode(wrongFeatures),runtime,"duplicate selected feature");
        wrongFeatures.put("selected_features",new JSONArray().put("development-feedback-core"));
        reject(encode(wrongFeatures),runtime,"missing selected feature");
        wrongFeatures.put("selected_features",new JSONArray().put("development-feedback-core").put("development-feedback-debug-host").put("unknown"));
        reject(encode(wrongFeatures),runtime,"extra selected feature");
        for(String key:Arrays.asList("schema","app_lane","owner_package","property_policy")) {
            JSONObject wrong=new JSONObject(new String(runtime,StandardCharsets.UTF_8));wrong.put(key,"wrong");
            reject(lock,encode(wrong),"wrong runtime "+key);
        }
        for(String key:selectedRuntime.keySet()) {
            JSONObject missing=new JSONObject(new String(runtime,StandardCharsets.UTF_8));missing.remove(key);
            reject(lock,encode(missing),"missing runtime "+key);
        }
        JSONObject extraRuntime=new JSONObject(new String(runtime,StandardCharsets.UTF_8));extraRuntime.put("unexpected",true);
        reject(lock,encode(extraRuntime),"extra runtime key");
        extraRuntime.remove("unexpected");extraRuntime.put("properties",new JSONArray().put("unexpected"));
        reject(lock,encode(extraRuntime),"nonempty none-policy properties");
        reject(new byte[]{'{'},runtime,"malformed lock JSON");
        reject(lock,new byte[]{'{'},"malformed runtime JSON");
        byte[] changedRuntime=Arrays.copyOf(runtime,runtime.length+1);changedRuntime[runtime.length]=' ';
        Map<String,String> changed=AssetActivation.parse(lock,changedRuntime);
        check(!projected.get("runtime_profile_sha256").equals(changed.get("runtime_profile_sha256")),"raw runtime whitespace digest ignored");
        byte[] changedLock=Arrays.copyOf(lock,lock.length+1);changedLock[lock.length]=' ';
        changed=AssetActivation.parse(changedLock,runtime);
        check(!projected.get("feature_lock_sha256").equals(changed.get("feature_lock_sha256")),"raw lock whitespace digest ignored");

        byte[] oversizedRuntime=Arrays.copyOf(runtime,1048577);Arrays.fill(oversizedRuntime,runtime.length,oversizedRuntime.length,(byte)' ');
        reject(lock,oversizedRuntime,"oversized runtime input");
        byte[] oversizedLock=Arrays.copyOf(lock,1048577);Arrays.fill(oversizedLock,lock.length,oversizedLock.length,(byte)' ');
        reject(oversizedLock,runtime,"oversized lock input");
        System.out.println("AssetActivationTest PASS "+assertions+" assertions; real upstream org.json host parser, not Android runtime parity");
    }
}
