package io.github.mesmerprism.rustyquest.media;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Owner-scoped views over one sender pipeline and an optional injected receiver sink. */
public final class PackedStereoMediaOwnerSet implements AutoCloseable {
    private final long generation;
    private final PackedStereoPipeline pipeline;
    private final AtomicBoolean cleanupArmed = new AtomicBoolean();
    private final Map<String, MediaOwnerProvider> owners = new LinkedHashMap<>();

    public PackedStereoMediaOwnerSet(long generation, PackedStereoPipeline pipeline) {
        this(generation,pipeline,null);
    }
    public PackedStereoMediaOwnerSet(long generation, PackedStereoPipeline pipeline,
            MediaOwnerProvider receiverSink) {
        if (generation<=0 || pipeline==null) throw new IllegalArgumentException();
        this.generation=generation;this.pipeline=pipeline;
        for(String kind:new String[]{"source","processor","route","socket","codec","cleanup"})
            owners.put(kind,new Owner(kind));
        if(receiverSink!=null) owners.put("sink",receiverSink);
    }
    public MediaOwnerProvider provider(String ownerKind) {
        MediaOwnerProvider owner=owners.get(ownerKind);
        if(owner==null)throw new IllegalArgumentException("unsupported owner kind "+ownerKind);
        return owner;
    }
    @Override public void close(){pipeline.close();}

    private final class Owner implements MediaOwnerProvider {
        final String kind; final AtomicLong revision=new AtomicLong();
        final AtomicReference<String> state=new AtomicReference<>("stopped");
        Owner(String kind){this.kind=kind;}
        @Override public MediaProviderReadback execute(MediaOwnerAction action,
                CancellationHandle cancellation)throws Exception{
            require(action,cancellation);
            String actionKind=action.actionKind();
            if("stop".equals(actionKind)||"cleanup".equals(actionKind))return stop(action);
            if(!state.compareAndSet("stopped","starting"))throw new IllegalStateException("provider busy");
            try{
                if("cleanup".equals(kind)){cleanupArmed.set(true);}
                else if("route".equals(kind)){pipeline.validateRoute();}
                else if("socket".equals(kind)){pipeline.startSocket();}
                else if("codec".equals(kind)){pipeline.startCodec();}
                else if("processor".equals(kind)){pipeline.startProcessor();}
                else if("source".equals(kind)){pipeline.startSource();}
                cancellation.requireCurrent(generation);
                String observed="arm_receiver".equals(actionKind)?"receiver_armed":
                        ("arm_cleanup".equals(actionKind)?"cleanup_armed":"started");
                state.set(observed);return readback(action,observed);
            }catch(Exception failure){state.set("failed");throw failure;}
        }
        @Override public MediaProviderReadback compensate(MediaOwnerAction action,
                CancellationHandle cancellation)throws Exception{require(action,cancellation);return stop(action);}
        private MediaProviderReadback stop(MediaOwnerAction action)throws Exception{
            String observed="cleanup".equals(kind)||"cleanup".equals(action.actionKind())?"cleaned":"stopped";
            state.set("stopping");
            // Every owner shares this connected graph. Compensation may begin after
            // any partially completed stage, so the first reverse action atomically
            // aborts and verifies the whole graph; later closures are idempotent.
            pipeline.stopAndVerify("owner_"+kind+"_stop");
            if("cleanup".equals(kind)){pipeline.finishCleanup();cleanupArmed.set(false);}
            state.set(observed);return readback(action,observed);
        }
        private void require(MediaOwnerAction action,CancellationHandle cancellation){
            if(!kind.equals(action.ownerKind()))throw new IllegalArgumentException("owner kind mismatch");
            cancellation.requireCurrent(generation);
        }
        private String handle(){return pipeline.handleId()+":"+kind+":g"+generation;}
        private MediaProviderReadback readback(MediaOwnerAction action,String observed){
            long next=revision.incrementAndGet();return new MediaProviderReadback(action,handle(),next,observed,
                    action.actionId()+":"+action.sequence()+":"+kind+":"+next);}
        @Override public MediaRuntimeSnapshot snapshot(){
            String current=state.get();
            if(pipeline.failed() && !("stopped".equals(current)||"cleaned".equals(current))) current="failed";
            boolean terminal=pipeline.terminal() && ("stopped".equals(current)
                    ||"cleaned".equals(current)||"failed".equals(current));
            return new MediaRuntimeSnapshot(generation,revision.get(),current,terminal,
                    cleanupArmed.get()?"cleanup_armed":"",handle());
        }
    }
}
