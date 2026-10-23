package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import org.json.JSONObject;

/** Executes the complete production collector with modeled Android context and native accessor. */
public final class OperationDiagnosticsHost {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static JSONObject correlation(String id)throws Exception{
        return new JSONObject().put("original_id",id).put("request_sha256","b".repeat(64)).put("outcome_sha256",JSONObject.NULL)
            .put("app_generation",73).put("native_generation",65).put("attempt_sequence",JSONObject.NULL);
    }
    public static void main(String[] args)throws Exception{
        File root=new File(args[0]);EmbeddedDuplexOperationDiagnostics.initialize(root);
        String id="a".repeat(32);
        if(args.length>1&&args[1].equals("read-bounds")){
            JSONObject details=new JSONObject().put("expected_generation",Long.MAX_VALUE).put("observed_generation",Long.MAX_VALUE)
                .put("receipt_known",false).put("native_pending",true).put("native_cause_present",false);
            for(int i=0;i<32;i++)for(int j=0;j<5;j++)EmbeddedDuplexOperationDiagnostics.record("start",j==0?"primary":"cleanup","java","media_owner","stress_"+j,
                new IllegalStateException("PRIVATE_STRESS"),correlation(String.format("%032x",i)),"unknown","pending",details);
            JSONObject nativeCollection=EmbeddedDuplexOperationDiagnostics.collection(null);
            for(int i=0;i<nativeCollection.getJSONArray("operations").length();i++){
                JSONObject operation=nativeCollection.getJSONArray("operations").getJSONObject(i);org.json.JSONArray observations=new org.json.JSONArray().put(operation.getJSONObject("primary"));
                for(int j=0;j<operation.getJSONArray("secondary").length();j++)observations.put(operation.getJSONArray("secondary").getJSONObject(j));
                for(int j=0;j<observations.length();j++){
                    JSONObject maximum=new JSONObject();for(int k=0;k<12;k++)maximum.put(String.format("native_hash_%02d_%080d",k,k),"e".repeat(64));
                    check(maximum.toString().length()<=2048);observations.getJSONObject(j).put("safe_details",maximum);
                }
            }
            EmbeddedDuplexNative.snapshot=nativeCollection.toString();System.setProperty("test.root",root.toString());
            String read=EmbeddedDuplexOperationDiagnostics.read(new android.content.Context(),null,"1".repeat(32));
            check(read.length()<=65536&&read.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=128*1024&&!read.contains("PRIVATE_STRESS"));
            JSONObject merged=new JSONObject(read);long omitted=0;
            for(String name:new String[]{"java_observation","native_observation"}){
                JSONObject collection=merged.getJSONObject(name);omitted+=collection.getLong("dropped_count");
                for(int i=0;i<collection.getJSONArray("operations").length();i++){
                    JSONObject operation=collection.getJSONArray("operations").getJSONObject(i);
                    check(operation.getJSONObject("primary").getString("boundary").equals("stress_0"));
                    check(operation.getJSONArray("secondary").getJSONObject(3).getString("boundary").equals("stress_4"));
                }
            }
            check(omitted>0);check(EmbeddedDuplexOperationDiagnostics.collection(null).getJSONArray("operations").length()==32);
            JSONObject filtered=new JSONObject(EmbeddedDuplexOperationDiagnostics.read(new android.content.Context(),String.format("%032x",0),"1".repeat(32)));
            check(filtered.getJSONObject("java_observation").getJSONArray("operations").length()==1);
            check(filtered.getJSONObject("native_observation").getJSONArray("operations").length()==1);
            Files.writeString(new File(root,"BOUNDED_READ.json").toPath(),read);
            System.out.println("production merged read32x5 in BOTH layers: "+read.length()+"chars, dropped="+omitted+"; requested Original, primary/latest cleanup and stored data conserved");return;
        }
        if(args.length>1&&args[1].equals("reject")){
            JSONObject rejected=EmbeddedDuplexOperationDiagnostics.collection(null);check(rejected.getJSONArray("operations").length()==0);
            check(rejected.getLong("dropped_count")>0);check(!rejected.toString().contains("SECRET_PRIVATE"));
            System.out.println("corrupt public summary rejected without publication or lifecycle effects");return;
        }
        if(args.length>1&&args[1].equals("bounds")){
            for(int i=0;i<40;i++)EmbeddedDuplexOperationDiagnostics.record("start","primary","java","media_owner","bounded_retention",
                new RuntimeException(),correlation(String.format("%032x",i)),"unknown","unobserved",new JSONObject());
            check(EmbeddedDuplexOperationDiagnostics.collection(null).getJSONArray("operations").length()==32);
            String large="{\"response_frame\":\""+"x".repeat(900000)+"\"}";
            for(int i=0;i<6;i++)check(new JSONObject(EmbeddedDuplexOperationDiagnostics.storeNativeContext(String.format("%064x",i),large)).getString("status").equals("available"));
            File[] contexts=new File(root,"embedded-duplex-diagnostics").listFiles(f->f.getName().matches("[0-9a-f]{64}\\.json"));
            long bytes=0;for(File file:contexts)bytes+=file.length();check(bytes<=4*1024*1024);
            System.out.println("production retention: max32 summaries /4 secondary /4MiB private overall PASS");return;
        }
        if(args.length>1){
            JSONObject restored=EmbeddedDuplexOperationDiagnostics.collection(id).getJSONArray("operations").getJSONObject(0);
            check(restored.getJSONObject("primary").getString("boundary").equals("actual_provider_snapshot"));
            check(restored.getJSONArray("secondary").length()==4);check(restored.getLong("dropped_count")==3);
            System.out.println("persisted plain summaries survive a new process without runtime/authority construction");return;
        }
        check(EmbeddedDuplexOperationDiagnostics.collection(null).getJSONArray("operations").length()==0);
        EmbeddedDuplexOperationDiagnostics.initialize(new android.content.Context(){@Override public android.content.Context getApplicationContext(){throw new AssertionError("optional context unavailable");}});
        java.io.IOException primary=new java.io.IOException("SECRET_SIGNED_PAYLOAD");
        primary.addSuppressed(new SecurityException("SECRET_CLEANUP_TOKEN"));
        EmbeddedDuplexOperationDiagnostics.record("start","primary","java","media_owner","actual_provider_snapshot",primary,
            correlation(id),"unknown","unobserved",new JSONObject().put("expected_generation",65).put("observed_generation",66));
        for(int i=0;i<7;i++)EmbeddedDuplexOperationDiagnostics.record("start",i%2==0?"compensation":"cleanup","java","media_owner","abort_"+i,
            new java.util.concurrent.TimeoutException("SECRET_TIMEOUT"),correlation(id),"unknown","pending",new JSONObject());
        JSONObject snapshot=EmbeddedDuplexOperationDiagnostics.collection(id).getJSONArray("operations").getJSONObject(0);
        check(snapshot.getJSONObject("primary").getJSONObject("cause").getString("category").equals("io"));
        check(snapshot.getJSONArray("secondary").length()==4);check(snapshot.getLong("dropped_count")==3);
        String publicJson=snapshot.toString();check(!publicJson.contains("SECRET")&&!publicJson.contains("IOException")&&!publicJson.contains("frames"));
        JSONObject descriptor=snapshot.getJSONObject("primary").getJSONObject("private_context");
        check(descriptor.getString("context_id").matches("[0-9a-f]{64}"));
        byte[] detail=Files.readAllBytes(new File(root,"embedded-duplex-diagnostics/"+descriptor.getString("context_id")+".json").toPath());
        check(detail.length==descriptor.getLong("bytes"));String privateJson=new String(detail,java.nio.charset.StandardCharsets.UTF_8);
        check(privateJson.contains("IOException")&&privateJson.contains("SecurityException")&&privateJson.contains("SECRET_SIGNED_PAYLOAD"));
        EmbeddedDuplexOperationDiagnostics.record("prepare_peer_start","primary","java","codec","codec_prepare",
            new IllegalStateException("private_key=DO_NOT_RETAIN"),correlation("2".repeat(32)),"unknown","unobserved",new JSONObject());
        JSONObject redacted=EmbeddedDuplexOperationDiagnostics.collection("2".repeat(32)).getJSONArray("operations").getJSONObject(0).getJSONObject("primary").getJSONObject("private_context");
        String redactedText=Files.readString(new File(root,"embedded-duplex-diagnostics/"+redacted.getString("context_id")+".json").toPath());
        check(!redactedText.contains("DO_NOT_RETAIN")&&redactedText.contains("message_redacted\":true"));
        String jniContext=EmbeddedDuplexOperationDiagnostics.nativeThrowableContext(new IllegalStateException("unfamiliar codec detail"));
        check(jniContext.contains("unfamiliar codec detail"));
        check(!EmbeddedDuplexOperationDiagnostics.nativeThrowableContext(new IllegalStateException("-----BEGIN PRIVATE KEY-----SECRET-----END PRIVATE KEY-----")).contains("SECRET"));
        check(EmbeddedDuplexOperationDiagnostics.nativeThrowableContext(new RuntimeException(){@Override public String getMessage(){throw new AssertionError();}}).contains("unavailable"));
        EmbeddedDuplexOperationDiagnostics.record("prepare_peer_start","primary","java","codec","private_collector_failure",
            new RuntimeException(){@Override public String getMessage(){throw new AssertionError("optional detail failure");}},
            correlation("3".repeat(32)),"unknown","unobserved",new JSONObject());
        JSONObject unavailable=EmbeddedDuplexOperationDiagnostics.collection("3".repeat(32)).getJSONArray("operations").getJSONObject(0).getJSONObject("primary");
        check(unavailable.getJSONObject("private_context").getString("status").equals("unavailable"));
        check(unavailable.getString("boundary").equals("private_collector_failure"));check(unavailable.getString("effect_state").equals("unknown"));
        check(EmbeddedDuplexOperationDiagnostics.collection("f".repeat(32)).getJSONArray("operations").length()==0);
        String body="{\"public_key\":\"published\",\"signing_key_id\":\"public.id\",\"authorization_token\":\"PRIVATE_ALLOWED\",\"unicode\":\"é\"}";
        JSONObject nativeStored=new JSONObject(EmbeddedDuplexOperationDiagnostics.storeNativeContext("c".repeat(64),body));
        check(nativeStored.getString("status").equals("available"));
        byte[] exact=Files.readAllBytes(new File(root,"embedded-duplex-diagnostics/"+"c".repeat(64)+".json").toPath());
        check(Arrays.equals(exact,body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));check(exact.length==nativeStored.getLong("bytes"));
        check(new JSONObject(EmbeddedDuplexOperationDiagnostics.storeNativeContext("../path","{}")).getString("status").equals("unavailable"));
        check(new JSONObject(EmbeddedDuplexOperationDiagnostics.storeNativeContext("d".repeat(64),"{\"private_key\":\"NO\"}")).getString("status").equals("unavailable"));
        check(new JSONObject(EmbeddedDuplexOperationDiagnostics.storeNativeContext("d".repeat(64),"x".repeat(1024*1024+1))).getString("status").equals("truncated"));
        for(Throwable failure:new Throwable[]{new SecurityException(),new java.net.SocketTimeoutException(),new InterruptedException(),new java.util.concurrent.CancellationException(),new RuntimeException("NEW_UNFAMILIAR")}){
            String category=EmbeddedDuplexOperationDiagnostics.category(failure);check(!category.isEmpty());
        }
        JSONObject before=new JSONObject(EmbeddedDuplexOperationDiagnostics.collection(id).toString());
        // An unavailable optional sink cannot alter the already retained actual failure.
        check(new JSONObject(EmbeddedDuplexOperationDiagnostics.storeNativeContext("c".repeat(64),"{\"changed\":true}")).getString("status").equals("unavailable"));
        check(before.toString().equals(EmbeddedDuplexOperationDiagnostics.collection(id).toString()));
        System.setProperty("test.root",root.toString());
        String nativeEmpty=EmbeddedDuplexNative.snapshot;
        JSONObject nativeSnapshot=new JSONObject(snapshot.toString());nativeSnapshot.getJSONObject("primary").put("private_context",nativeStored);
        EmbeddedDuplexNative.snapshot=new JSONObject().put("schema","rusty.quest.operation_diagnostics_collection.v1")
            .put("operations",new org.json.JSONArray().put(nativeSnapshot)).put("dropped_count",0).put("retention",new JSONObject()).toString();
        JSONObject read=new JSONObject(EmbeddedDuplexOperationDiagnostics.read(new android.content.Context(),id,"1".repeat(32)));
        check(read.getJSONObject("native_observation").getJSONArray("operations").getJSONObject(0).getJSONObject("primary").getJSONObject("private_context").getString("status").equals("available"));
        exact[0]='x';Files.write(new File(root,"embedded-duplex-diagnostics/"+"c".repeat(64)+".json").toPath(),exact);
        read=new JSONObject(EmbeddedDuplexOperationDiagnostics.read(new android.content.Context(),id,"1".repeat(32)));
        check(read.getString("native_status").equals("available"));
        check(read.getJSONObject("native_observation").getJSONArray("operations").getJSONObject(0).getJSONObject("primary").getJSONObject("private_context").getString("status").equals("unavailable"));
        check(EmbeddedDuplexOperationDiagnostics.evictNativeContext("c".repeat(64)));check(EmbeddedDuplexOperationDiagnostics.evictNativeContext("c".repeat(64)));
        check(!EmbeddedDuplexOperationDiagnostics.evictNativeContext("../outside"));EmbeddedDuplexNative.snapshot=nativeEmpty;
        String envelope=EmbeddedDuplexOperationDiagnostics.read(new android.content.Context(),id,"1".repeat(32));
        check(new JSONObject(envelope).getString("challenge").equals("1".repeat(32)));
        Files.write(new File(root,"MODELED_PUBLIC_ENVELOPE.json").toPath(),envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("production Java collector: primary/secondary, privacy, correlation, exact private bytes, bounds, optional sink refusal PASS");
    }
}
