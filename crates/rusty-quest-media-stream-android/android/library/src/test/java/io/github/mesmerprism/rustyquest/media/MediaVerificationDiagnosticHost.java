package io.github.mesmerprism.rustyquest.media;

import org.json.JSONObject;

/** Real packaged registry/verifier; providers model resource faults, not verification logic. */
public final class MediaVerificationDiagnosticHost {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static String ticket(String action)throws Exception{
        return new JSONObject().put("$schema",MediaOwnerAction.SCHEMA).put("capability","cap.test").put("executor_generation",7)
            .put("action_id","action.test").put("authority_epoch_id","epoch.test").put("media_acceptance_authority_revision",1)
            .put("expected_runtime_revision",0).put("client_id","client.test").put("lease_id","lease.test").put("sequence",0)
            .put("operation",action.equals("start")?"start":"stop").put("owner_kind","source").put("action_kind",action)
            .put("owner_id","owner.test").put("provider_kind","provider.test").put("resource_id","resource.test").toString();
    }
    static final class Provider implements MediaOwnerProvider {
        String fault="none",state="started";boolean terminal;long generation=7,revision=1;String handle="handle.test";
        public MediaProviderReadback execute(MediaOwnerAction action,CancellationHandle cancellation)throws Exception{
            if(fault.equals("permission"))throw new SecurityException("PRIVATE_RESOURCE");
            if(fault.equals("io"))throw new java.io.IOException("PRIVATE_RESOURCE");
            return new MediaProviderReadback(action,"handle.test",1,state,"receipt.test");
        }
        public MediaProviderReadback compensate(MediaOwnerAction action,CancellationHandle cancellation)throws Exception{return execute(action,cancellation);}
        public MediaRuntimeSnapshot snapshot(){if(fault.equals("snapshot"))throw new IllegalStateException("UNFAMILIAR_RESOURCE_FAILURE");return new MediaRuntimeSnapshot(generation,revision,state,terminal,"private detail",handle);}
    }
    static PackagedAndroidMediaOwnerRegistry registry(Provider provider){return new PackagedAndroidMediaOwnerRegistry(7,
        new MediaProductBinding.Builder("product.test").bind("source","owner.test","provider.test","resource.test",provider).build());}
    static void refused(PackagedAndroidMediaOwnerRegistry registry,String ticket,String readback,String expected){
        check(registry.verifyAndReadEvidence(ticket,readback)==null);
        MediaVerificationDiagnostic diagnostic=registry.takeVerificationDiagnostic();check(diagnostic!=null&&diagnostic.boundary.equals(expected));
        check(registry.takeVerificationDiagnostic()==null);
    }
    public static void main(String[] args)throws Exception{
        String ticket=ticket("start");Provider provider=new Provider();PackagedAndroidMediaOwnerRegistry registry=registry(provider);
        refused(registry,ticket,null,"readback_bounds");refused(registry,"invalid","{}","ticket_parse");refused(registry,ticket,"invalid","readback_parse");
        refused(registry,ticket,"{}","readback_schema");
        String readback=registry.execute(ticket,false);
        refused(registry,new JSONObject(ticket).put("executor_generation",8).toString(),readback,"executor_generation");
        refused(registry,ticket,new JSONObject(readback).put("client_id","foreign").toString(),"readback_binding");
        refused(registry,ticket,new JSONObject(readback).put("receipt_id","foreign").toString(),"issued_receipt_binding");
        provider.fault="snapshot";refused(registry,ticket,readback,"provider_snapshot");
        provider.fault="none";provider.generation=8;refused(registry,ticket,readback,"snapshot_generation");
        provider.generation=7;provider.revision=2;refused(registry,ticket,readback,"snapshot_revision");
        provider.revision=1;provider.state="other";refused(registry,ticket,readback,"snapshot_state");
        provider.state="started";provider.handle="other";refused(registry,ticket,readback,"snapshot_handle");
        provider.handle="handle.test";check(registry.verifyAndReadEvidence(ticket,readback)!=null);check(registry.takeVerificationDiagnostic()==null);
        refused(registry,ticket,readback,"issued_receipt_binding");
        provider=new Provider();registry=registry(provider);readback=registry.execute(ticket,false);registry.close();
        refused(registry,ticket,readback,"cancellation_generation");
        provider=new Provider();provider.state="stopped";registry=registry(provider);String stop=ticket("stop");readback=registry.execute(stop,false);
        refused(registry,stop,readback,"cleanup_terminal");provider.terminal=true;check(registry.verifyAndReadEvidence(stop,readback)!=null);
        for(String fault:new String[]{"permission","io"}){
            provider=new Provider();provider.fault=fault;registry=registry(provider);
            try{registry.execute(ticket,false);throw new AssertionError();}catch(RuntimeException actual){check(fault.equals("permission")?actual instanceof SecurityException:actual.getCause() instanceof java.io.IOException);}
        }
        System.out.println("production registry: 15 refusal controls, unchanged success/consumption, original provider causes PASS");
    }
}
