package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual production loader/selector and native pure function; Android owners are not entered. */
public final class FrozenEffectiveInputsHost {
    static int checks;
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); checks++; }
    interface Action { void run() throws Exception; }
    static void refuse(Action action) throws Exception {
        try { action.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("expected refusal");
    }
    static String digest(byte[] bytes) throws Exception {
        StringBuilder result=new StringBuilder();
        for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format("%02x",value&255));
        return result.toString();
    }
    static byte[] profile(JSONObject route, int subnet, int port) throws Exception {
        Class<?> fixture=Class.forName("io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraControlProfileTest");
        java.lang.reflect.Method valid=fixture.getDeclaredMethod("validProfile"); valid.setAccessible(true);
        JSONObject value=(JSONObject)valid.invoke(fixture.getConstructor().newInstance());
        JSONArray selected=new JSONArray();
        for(int i=0;i<2;i++) {
            String host="10."+subnet+".0."+(11+i);
            selected.put(new JSONObject().put("peer_id",route.getJSONArray("peers").getJSONObject(i).getString("peer_id"))
                    .put("media_endpoint",new JSONObject().put("protocol","rmanvid-v4-packed-stereo").put("host",host).put("port",port))
                    .put("control_endpoint",new JSONObject().put("protocol","rqod1").put("host",host).put("port",port+1)));
        }
        return value.put("peer_network_endpoints",selected).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    static String nativeCall(String binary,String mode,String request)throws Exception {
        Process child=new ProcessBuilder(binary,mode).start();
        try(java.io.OutputStream input=child.getOutputStream()){input.write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        String result=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        String error=new String(child.getErrorStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        if(child.waitFor()!=0)throw new IllegalArgumentException(error);
        return result;
    }
    static FrozenEffectiveInputs publishAndSelect(Path active,byte[] bytes,FrozenEffectiveInputs.Selector selector)throws Exception {
        Path pending=Files.createTempFile(active.getParent(),".profile-",".pending");
        Files.write(pending,bytes);
        Files.move(pending,active,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try(InputStream input=Files.newInputStream(active)){return selector.select(input.readNBytes(65537));}
    }
    static JSONObject runtimeBindings(JSONObject route) {
        return new JSONObject().put("adapter_id","adapter.endpoint.host").put("admission_authority_id","authority.endpoint.host")
            .put("grant_id","grant.endpoint.host").put("grant_expires_at_ms",2592001000L).put("lease_expires_at_ms",2592001000L)
            .put("setup_credential_horizon_ms",2592000000L).put("max_token_ttl_ms",30000)
            .put("validation_wall_unix_ms",1000).put("validation_monotonic_elapsed_ns",1000000)
            .put("validation_epoch_entropy_hex","04".repeat(32)).put("embedded_duplex",new JSONObject()
              .put("$schema","rusty.quest.embedded_duplex.development_authority_config.v1")
              .put("runtime_host_id","host."+route.getJSONArray("peers").getJSONObject(0).getString("peer_id"))
              .put("trusted_operator_ids",new JSONArray().put("operator.endpoint.host"))
              .put("trusted_key_fingerprints",new JSONArray().put("fingerprint.endpoint.host"))
              .put("trusted_adapter_ids",new JSONArray().put("adapter.endpoint.host"))
              .put("trusted_media_revoker_ids",new JSONArray().put("revoker.endpoint.host")));
    }
    public static void main(String[] args) throws Exception {
        Path assets=Path.of(args[0]);String nativeBinary=args[1];
        String manifestRaw=Files.readString(assets.resolve("product-input-manifest.json"));
        JSONObject manifest=new JSONObject(manifestRaw),identity=manifest.getJSONObject("package");
        EmbeddedDuplexPackagedInputs base=EmbeddedDuplexPackagedInputs.load(new EmbeddedDuplexPackagedInputs.AssetReader() {
            public InputStream open(String name)throws Exception{return Files.newInputStream(assets.resolve(name));}
            public String[] list()throws Exception{try(java.util.stream.Stream<Path> files=Files.list(assets)){return files.map(p->p.getFileName().toString()).toArray(String[]::new);}}
        },digest(manifestRaw.getBytes(java.nio.charset.StandardCharsets.UTF_8)),identity.getString("application_id"),
          identity.getString("signing_certificate_sha256"),EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A);
        int[] calls={0};boolean[] corrupt={false};
        FrozenEffectiveInputs.Deriver nativeDeriver=request->{
            calls[0]++;
            String result=nativeCall(nativeBinary,"--derive",request);
            if(corrupt[0])return new JSONObject(result).put("lineage",new JSONObject()).toString();
            return result;
        };
        FrozenEffectiveInputs.Selector selector=new FrozenEffectiveInputs.Selector(base,41,nativeDeriver);
        check(selector.current().json("route-configuration.json").equals(base.json("route-configuration.json")),"baseline exact");
        JSONObject route=new JSONObject(base.json("route-configuration.json"));
        byte[] a=profile(route,20,41000),b=profile(route,21,43000);
        Path active=Path.of(nativeBinary).getParent().resolve("control-profiles/active.profile.json");
        Files.createDirectories(active.getParent());
        long start=System.nanoTime();
        FrozenEffectiveInputs first=publishAndSelect(active,a,selector),second=publishAndSelect(active,b,selector),restored=publishAndSelect(active,a,selector);
        long editRevertNs=System.nanoTime()-start;
        check(!first.digest("route-configuration.json").equals(second.digest("route-configuration.json")),"B actually changes route");
        check(first.digest("route-configuration.json").equals(restored.digest("route-configuration.json")),"A restores exact effective route");
        check(first.verifiedBase==second.verifiedBase&&second.verifiedBase==base,"one verified installed base");
        check(first.generation==41&&second.generation==41,"exact captured owner generation");
        JSONObject selectedReadback=new JSONObject(selector.selectionReadback());
        check(selectedReadback.getString("phase").equals("selected")&&selectedReadback.getLong("generation")==41,"truthful selected phase");
        check(selectedReadback.getJSONObject("lineage").getString("effective_route_sha256").equals(restored.digest("route-configuration.json")),"readback joins accepted route");
        for(String name:new String[]{"product-input-manifest.json","client-lock.json","planning-feature-lock.json","packed-stereo-profile.json"})
            check(first.json(name).equals(second.json(name)),"immutable input "+name);
        check(new JSONObject(second.lineageJson).getString("base_route_sha256").equals(base.digest("route-configuration.json")),"base route keeps its original meaning");
        JSONObject runtime=runtimeBindings(route);
        refuse(()->first.packagedConfigRequest(runtime));
        refuse(()->EmbeddedDuplexEnrollmentRequest.projectPublicFacts(first,"key.endpoint.host",new byte[32]));
        FrozenEffectiveInputs.Selector generationA=new FrozenEffectiveInputs.Selector(base,51,nativeDeriver);
        generationA.select(a);FrozenEffectiveInputs consumeA=generationA.freeze();
        FrozenEffectiveInputs.Selector generationB=new FrozenEffectiveInputs.Selector(base,52,nativeDeriver);
        generationB.select(b);FrozenEffectiveInputs consumeB=generationB.freeze();
        EmbeddedDuplexBootstrap.ConfigAssembler assembler=request->nativeCall(nativeBinary,"--assemble",request);
        EmbeddedDuplexBootstrap.Prepared preparedA=EmbeddedDuplexBootstrap.prepare(consumeA,runtime,assembler);
        EmbeddedDuplexBootstrap.Prepared preparedB=EmbeddedDuplexBootstrap.prepare(consumeB,runtime,assembler);
        check(preparedA.localControlPort==41001&&preparedB.localControlPort==43001,"actual Bootstrap consumes derived control ports");
        check(preparedB.localControlHost.equals("10.21.0.11"),"actual Bootstrap consumes derived host");
        check(!preparedA.runtimeConfigSha256.equals(preparedB.runtimeConfigSha256),"actual canonical Native configuration changes");
        JSONObject configA=new JSONObject(preparedA.runtimeConfigJson),configB=new JSONObject(preparedB.runtimeConfigJson);
        check(configA.getJSONObject("embedded_duplex").toString().equals(configB.getJSONObject("embedded_duplex").toString()),"same trusted authority policy");
        EmbeddedDuplexEnrollmentRequest enrollmentA=EmbeddedDuplexEnrollmentRequest.projectPublicFacts(consumeA,"key.endpoint.host",new byte[32]);
        EmbeddedDuplexEnrollmentRequest enrollmentB=EmbeddedDuplexEnrollmentRequest.projectPublicFacts(consumeB,"key.endpoint.host",new byte[32]);
        check(enrollmentA.manifestSha256.equals(enrollmentB.manifestSha256)&&enrollmentA.routeSha256.equals(enrollmentB.routeSha256),"enrollment retains immutable installed hashes");
        check(enrollmentA.localPeerId.equals(enrollmentB.localPeerId)&&enrollmentA.remotePeerId.equals(enrollmentB.remotePeerId)
            &&enrollmentA.localDeviceId.equals(enrollmentB.localDeviceId)&&enrollmentA.localKeyId.equals(enrollmentB.localKeyId)
            &&enrollmentA.localPublicKeyHex.equals(enrollmentB.localPublicKeyHex),"enrollment identity and public key preserved");
        check(enrollmentA.effectiveInputLineageJson.equals(first.lineageJson)&&enrollmentB.effectiveInputLineageJson.equals(second.lineageJson),"same snapshot supplies enrollment and Bootstrap");
        check(!enrollmentA.sameFacts(enrollmentB),"changed effective association cannot reuse old enrollment facts");
        int before=calls[0];
        refuse(()->selector.select("{}".getBytes()));
        refuse(()->selector.select(new byte[65537]));
        String valid=new String(b,java.nio.charset.StandardCharsets.UTF_8);
        refuse(()->selector.select((valid.substring(0,valid.length()-1)+",\"schema\":\"duplicate\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check(calls[0]==before,"invalid byte/profile inputs never enter native derivation");
        check(selector.current()==restored,"invalid input retains exact accepted object");
        corrupt[0]=true;refuse(()->selector.select(b));corrupt[0]=false;
        check(selector.current()==restored,"native response refusal retains exact accepted object");
        FrozenEffectiveInputs frozen=selector.freeze();
        check(selector.frozen()&&frozen==restored,"freeze returns exact shared snapshot");
        check(new JSONObject(selector.selectionReadback()).getString("phase").equals("frozen"),"truthful frozen phase");
        refuse(()->first.packagedConfigRequest(runtime));
        refuse(()->EmbeddedDuplexEnrollmentRequest.projectPublicFacts(first,"key.endpoint.host",new byte[32]));
        before=calls[0];refuse(()->selector.select(b));
        check(calls[0]==before&&selector.current()==frozen,"frozen edit has no native call or accepted mutation");
        check(new JSONObject(selector.selectionReadback()).getJSONObject("lineage").getString("profile_sha256")
            .equals(new JSONObject(frozen.lineageJson).getString("profile_sha256")),"refused edit readback retains accepted profile");
        System.out.println(new JSONObject().put("passed",true).put("controls",checks).put("resident_jvm",true)
                .put("edit_revert_ms",editRevertNs/1000000.0).put("edit_revert_scope","three actual atomic profile publications and bounded reads, A->B->A native derivation/acceptance; one resident JVM")
                .put("limits","Actual Java/native data path; Android Context, private enrollment, authority admission and sockets not entered."));
    }
}
